package sangitam.desktop.playback;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sangitam.desktop.storage.AudioSettingsStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AudioSettingsTest {
    @TempDir Path directory;

    @Test void preferencesRoundTripAndReplaceWithoutTouchingLibrary() throws Exception {
        Path data = directory.resolve("user-data");
        Files.createDirectories(data);
        Path database = data.resolve("library.db");
        Files.writeString(database, "existing library");
        AudioSettingsStore store = new AudioSettingsStore(data.resolve("audio.properties"));
        assertEquals(AudioSettings.defaults(), store.load());
        var gains = new ArrayList<>(AudioSettings.defaults().bands());
        gains.set(3, 5d);
        var settings = new AudioSettings(true, gains, -4, AudioSettings.ReplayGain.ALBUM);
        store.save(settings);
        assertEquals(settings, store.load());
        store.save(AudioSettings.defaults());
        assertEquals(AudioSettings.defaults(), store.load());
        assertEquals("existing library", Files.readString(database));
        try (var files = Files.list(data)) { assertEquals(2, files.count()); }
    }

    @Test void rejectsCorruptPreferencesAndNonFiniteOrOutOfRangeGains() throws Exception {
        AudioSettingsStore store = new AudioSettingsStore(directory.resolve("audio.properties"));
        Files.writeString(directory.resolve("audio.properties"), "band.0=NaN\nreplayGain=TRACK\n");
        assertThrows(java.io.IOException.class, store::load);
        Files.writeString(directory.resolve("audio.properties"), "replayGain=INVALID\n");
        assertThrows(java.io.IOException.class, store::load);
        assertThrows(IllegalArgumentException.class, () -> new AudioSettings(true, List.of(0d), 0, AudioSettings.ReplayGain.OFF));
        assertThrows(IllegalArgumentException.class, () -> new AudioSettings(false, AudioSettings.defaults().bands(), 1, AudioSettings.ReplayGain.OFF));
    }

    @Test void equalizerDoesNotAutomaticallyLowerVolumeAndBypassRetainsCustomCurve() {
        var gains = new ArrayList<>(AudioSettings.defaults().bands());
        gains.set(1, 6d);
        var enabled = new AudioSettings(true, gains, -3, AudioSettings.ReplayGain.TRACK);
        gains.set(1, 12d); // caller cannot mutate the saved curve
        assertEquals(-3, enabled.effectivePreampDb());
        assertEquals(0, new AudioSettings(true, enabled.bands(), 0, AudioSettings.ReplayGain.OFF).effectivePreampDb());
        assertEquals(6, enabled.bands().get(1));
        assertThrows(UnsupportedOperationException.class, () -> enabled.bands().set(0, 9d));
        var bypassed = new AudioSettings(false, enabled.bands(), -3, AudioSettings.ReplayGain.TRACK);
        assertEquals(0, bypassed.effectivePreampDb());
        assertEquals(enabled.bands(), bypassed.bands());
        assertTrue(List.of(enabled.vlcOptions()).contains("--audio-replay-gain-default=0"));
        assertTrue(List.of(enabled.vlcOptions()).contains("--audio-replay-gain-peak-protection"));
    }

    @Test void percentageVolumeMatchesVlcIncludingBoostAndMute() {
        assertEquals(0, AudioGain.linear(0));
        assertEquals(1, AudioGain.linear(1));
        assertEquals(0.125, AudioGain.linear(0.5), 1e-12);
        assertEquals(3.375, AudioGain.linear(1.5), 1e-12);
        assertEquals("50%", AudioGain.label(0.5));
        assertEquals("0%", AudioGain.label(0));
        assertEquals("150%", AudioGain.label(1.5));
        assertEquals(0, AudioGain.linear(Double.NaN));
        assertEquals(100, AudioGain.vlcVolume(1));
        assertEquals(0, AudioGain.vlcVolume(0));
        assertEquals(150, AudioGain.vlcVolume(1.5));
        assertEquals(150, AudioGain.vlcVolume(2));
        assertEquals(0, AudioGain.vlcVolume(-1));
        assertEquals(4, AudioGain.fromDecibels(AudioGain.vlcEqualizerPreamp(0)), 1e-6);
        for (int percent = 0; percent <= 150; percent++)
            assertEquals(percent, AudioGain.vlcVolume(percent / 100d));
    }
}
