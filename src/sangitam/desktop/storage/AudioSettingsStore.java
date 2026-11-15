package sangitam.desktop.storage;

import sangitam.desktop.playback.AudioSettings;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Properties;

/** Non-secret preferences alongside the library, with replacement only after a complete write. Call off EDT. */
public final class AudioSettingsStore {
    private final Path file;
    public AudioSettingsStore(Path file) { this.file = file.toAbsolutePath().normalize(); }

    public AudioSettings load() throws IOException {
        if (!Files.exists(file)) return AudioSettings.defaults();
        Properties values = new Properties();
        try (var input = Files.newInputStream(file)) { values.load(input); }
        try {
            var bands = new ArrayList<Double>();
            for (int i = 0; i < 10; i++) bands.add(Double.parseDouble(values.getProperty("band." + i, "0")));
            return new AudioSettings(Boolean.parseBoolean(values.getProperty("equalizer", "false")), bands,
                Double.parseDouble(values.getProperty("preamp", "0")),
                AudioSettings.ReplayGain.valueOf(values.getProperty("replayGain", "OFF")));
        } catch (IllegalArgumentException error) {
            throw new IOException("Saved audio settings are invalid. Defaults are in use; save to replace them.", error);
        }
    }

    public void save(AudioSettings settings) throws IOException {
        Properties values = new Properties();
        values.setProperty("equalizer", Boolean.toString(settings.equalizerEnabled()));
        values.setProperty("preamp", Double.toString(settings.preampDb()));
        values.setProperty("replayGain", settings.replayGain().name());
        for (int i = 0; i < 10; i++) values.setProperty("band." + i, settings.bands().get(i).toString());
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "audio-", ".properties.tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) { values.store(output, "Sangitam audio settings"); }
            try { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
