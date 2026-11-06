package sangitam.desktop.app;

import sangitam.desktop.ui.MainFrame;
import sangitam.desktop.ui.Theme;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

/**
 * Sangitam desktop entry point.
 * IMPORTANT: All Swing construction runs on the Event Dispatch Thread to prevent intermittent UI bugs.
 */
public final class SangitamApp {
    private SangitamApp() {
    }

    public static void main(String[] args) {
        long mainStarted = System.nanoTime();
        Path report = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> System.setProperty("sangitam.dataDir", Path.of(args[++i]).toAbsolutePath().toString());
                case "--startup-report" -> report = Path.of(args[++i]).toAbsolutePath();
                case "--offline-profiles" -> System.setProperty("sangitam.artistOnline", "false");
                case "--package-check" -> { PackageCheck.run(Path.of(args[++i])); return; }
                default -> throw new IllegalArgumentException("Unknown launch option: " + args[i]);
            }
        }
        StartupTrace trace = new StartupTrace(report, mainStarted);
        trace.mark("main");
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            MainFrame frame = new MainFrame(trace);
            frame.setVisible(true);
        });
    }
}
