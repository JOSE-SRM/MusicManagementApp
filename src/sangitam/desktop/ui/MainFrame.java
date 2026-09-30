package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Main application window and composition root for the Swing UI.
 * IMPORTANT: This class wires screens together; individual UI implementations belong in their own files.
 */
public final class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final NotificationCenter notifications = new NotificationCenter();
    private LibraryPanel libraryPanel;
    private boolean compactLayout;

    public MainFrame() {
        super("Sangitam — Music Management");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1360, 820);
        setLocationRelativeTo(null);
        setContentPane(buildWindow());
        setJMenuBar(new AppMenuBar(this::openImportDialog, this::openCommandPalette, notifications::show, this::dispose));
        installKeyboardShortcuts();
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { updateResponsiveLayout(); }
        });
    }

    private JComponent buildWindow() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.BLACK);

        screens.setBackground(Theme.BLACK);
        screens.add(new DashboardPanel(this::openImportDialog, notifications::show), "home");
        screens.add(new SearchPanel(notifications::show), "search");

        JPanel workspace = new JPanel(new BorderLayout());
        workspace.setBackground(Theme.BLACK);
        workspace.add(screens, BorderLayout.CENTER);
        libraryPanel = new LibraryPanel(this::openImportDialog, notifications::show);
        workspace.add(libraryPanel, BorderLayout.EAST);

        root.add(new NavigationPanel(this::showScreen, this::openImportDialog,
            this::toggleLibrary, this::openCommandPalette, notifications::show), BorderLayout.WEST);
        root.add(workspace, BorderLayout.CENTER);

        JPanel lower = new JPanel(new BorderLayout());
        lower.add(new PlayerBar(notifications::show), BorderLayout.CENTER);
        lower.add(notifications, BorderLayout.SOUTH);
        root.add(lower, BorderLayout.SOUTH);
        return root;
    }

    private void showScreen(String name) {
        screenLayout.show(screens, name);
        notifications.show(Character.toUpperCase(name.charAt(0)) + name.substring(1) + " opened");
    }

    private void openImportDialog() {
        new ImportMediaDialog(this, notifications::show).setVisible(true);
    }

    private void installKeyboardShortcuts() {
        int shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
            .put(KeyStroke.getKeyStroke(KeyEvent.VK_K, shortcut), "quickActions");
        getRootPane().getActionMap().put("quickActions", new AbstractAction() {
            private static final long serialVersionUID = 1L;
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { openCommandPalette(); }
        });
    }

    private void openCommandPalette() {
        Map<String, Runnable> commands = new LinkedHashMap<>();
        commands.put("Shape a listening session", () -> showScreen("home"));
        commands.put("Search music", () -> showScreen("search"));
        commands.put("Add files or a folder", this::openImportDialog);
        commands.put("Show or hide library", this::toggleLibrary);
        commands.put("Open settings", () -> notifications.show("Settings selected"));
        commands.put("Play or pause", () -> notifications.show("Play / pause pressed"));
        new CommandPaletteDialog(this, commands).setVisible(true);
    }

    private void toggleLibrary() {
        libraryPanel.setVisible(!libraryPanel.isVisible());
        libraryPanel.getParent().revalidate();
        notifications.show(libraryPanel.isVisible() ? "Library shown" : "Library hidden");
    }

    /** On narrow windows the library yields space to the primary task instead of squeezing the composer. */
    private void updateResponsiveLayout() {
        boolean nowCompact = getWidth() < 1180;
        if (nowCompact == compactLayout) return;
        compactLayout = nowCompact;
        libraryPanel.setVisible(!nowCompact);
        libraryPanel.getParent().revalidate();
    }
}
