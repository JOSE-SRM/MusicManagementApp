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
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            javax.swing.Timer firstPaint = new javax.swing.Timer(60, event -> frame.loadWorkspace());
            firstPaint.setRepeats(false);
            firstPaint.start();
        });
    }
}
