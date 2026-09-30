package sangitam.desktop.ui;

import javax.swing.*;
import java.awt.*;

/**
 * Main application window and composition root for the Swing UI.
 * IMPORTANT: This class wires screens together; individual UI implementations belong in their own files.
 */
public final class MainFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private final CardLayout screenLayout = new CardLayout();
    private final JPanel screens = new JPanel(screenLayout);
    private final NotificationCenter notifications = new NotificationCenter();

    public MainFrame() {
        super("Sangitam — Music Management");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(960, 640));
        setSize(1360, 820);
        setLocationRelativeTo(null);
        setContentPane(buildWindow());
        setJMenuBar(new AppMenuBar(this::openImportDialog, notifications::show, this::dispose));
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
        workspace.add(new LibraryPanel(this::openImportDialog, notifications::show), BorderLayout.EAST);

        root.add(new NavigationPanel(this::showScreen, this::openImportDialog, notifications::show), BorderLayout.WEST);
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
}
