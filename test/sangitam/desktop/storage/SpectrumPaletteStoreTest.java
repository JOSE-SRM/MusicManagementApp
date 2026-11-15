package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.model.SpectrumPreferences;
import sangitam.desktop.model.SpectrumPreferences.*;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SpectrumPaletteStoreTest {
    @TempDir Path directory;

    @Test void displaySettingsPersistAcrossRestartAndPreserveLegacyPaletteAndVolume() throws Exception {
        Path database = directory.resolve("catalog.db");
        var first = new LibraryStore(database);
        first.initialize();
        assertEquals(SpectrumPreferences.defaults(), first.loadSpectrumPreferences());
        first.saveVolume(.48);
        try (var connection = first.connect(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO app_state VALUES('spectrum_palette','PLUM')");
        }
        assertEquals(new SpectrumPreferences(Palette.PLUM, Colors.VERTICAL, Heights.EXTEND_PEAKS),
            first.loadSpectrumPreferences());
        var chosen = new SpectrumPreferences(0x1030ce, 0xff5612, Colors.ACROSS, Heights.EXPONENTIAL);
        first.saveSpectrumPreferences(chosen);
        var reopened = new LibraryStore(database);
        reopened.initialize();
        assertEquals(chosen, reopened.loadSpectrumPreferences());
        assertEquals(.48, reopened.loadVolume());
    }
}
