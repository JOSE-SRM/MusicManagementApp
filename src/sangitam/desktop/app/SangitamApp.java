package sangitam.desktop.app;

import sangitam.desktop.ui.MainFrame;
import sangitam.desktop.ui.Theme;

import javax.swing.SwingUtilities;

/**
 * Sangitam desktop entry point.
 * IMPORTANT: All Swing construction runs on the Event Dispatch Thread to prevent intermittent UI bugs.
 */
public final class SangitamApp {
    private SangitamApp() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            new MainFrame().setVisible(true);
        });
    }
}
