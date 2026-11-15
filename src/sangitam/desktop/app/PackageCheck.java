package sangitam.desktop.app;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;

/** Verifies packaged runtime and dependencies before the application creates any windows. */
final class PackageCheck {
    static void run(Path result) {
        try {
            Class.forName("javafx.scene.media.MediaPlayer", false, PackageCheck.class.getClassLoader());
            Class.forName("uk.co.caprica.vlcj.factory.MediaPlayerFactory", false, PackageCheck.class.getClassLoader());
            try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:");
                 var query = connection.createStatement(); var row = query.executeQuery("SELECT sqlite_version()")) {
                if (!row.next()) throw new IllegalStateException("SQLite did not return its version");
                Files.writeString(result, "Package OK · Java " + Runtime.version() + " · SQLite " + row.getString(1));
            }
        } catch (Exception error) {
            throw new IllegalStateException("Packaged runtime check failed", error);
        }
    }
}
