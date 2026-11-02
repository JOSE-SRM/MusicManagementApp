package sangitam.desktop.storage;

import java.nio.file.Path;

/** Selects a user-data directory without putting a database into the Git checkout. */
public final class AppPaths {
    private AppPaths() { }

    public static Path libraryDatabase() {
        String override = System.getProperty("sangitam.dataDir");
        if (override != null && !override.isBlank()) return Path.of(override).resolve("library.db");
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "Sangitam", "library.db");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) return Path.of(appData, "Sangitam", "library.db");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        return (xdg == null || xdg.isBlank() ? Path.of(home, ".local", "share") : Path.of(xdg))
            .resolve("Sangitam").resolve("library.db");
    }
}
