package sangitam.desktop.playback;

import java.util.List;
import java.util.Objects;

/** Immutable, backend-independent audio preferences. Gains are in dB, not slider percentages. */
public record AudioSettings(boolean equalizerEnabled, List<Double> bands, double preampDb, ReplayGain replayGain) {
    // libVLC's ISO ten-band layout; JavaFX is configured with the same centre frequencies.
    public static final List<Double> FREQUENCIES = List.of(31.25, 62.5, 125.0, 250.0, 500.0,
        1000.0, 2000.0, 4000.0, 8000.0, 16000.0);
    public enum ReplayGain {
        OFF("Off", "none"), TRACK("Track", "track"), ALBUM("Album", "album");
        private final String label;
        private final String vlcMode;
        ReplayGain(String label, String vlcMode) { this.label = label; this.vlcMode = vlcMode; }
        public String vlcMode() { return vlcMode; }
        @Override public String toString() { return label; }
    }

    public AudioSettings {
        bands = List.copyOf(bands);
        Objects.requireNonNull(replayGain);
        if (bands.size() != FREQUENCIES.size()) throw new IllegalArgumentException("Ten EQ bands are required");
        for (double band : bands) if (!Double.isFinite(band) || band < -12 || band > 12)
            throw new IllegalArgumentException("EQ gain must be between -12 and +12 dB");
        if (!Double.isFinite(preampDb) || preampDb < -12 || preampDb > 0)
            throw new IllegalArgumentException("Preamp must be between -12 and 0 dB");
    }

    public static AudioSettings defaults() {
        return new AudioSettings(false, List.of(0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d, 0d), 0, ReplayGain.OFF);
    }

    /** EQ never changes the master level implicitly; only the user's preamp changes it. */
    public double effectivePreampDb() {
        return equalizerEnabled ? preampDb : 0;
    }

    public String[] vlcOptions() {
        return new String[] { "--audio-replay-gain-mode=" + replayGain.vlcMode(),
            "--audio-replay-gain-preamp=0", "--audio-replay-gain-default=0",
            "--audio-replay-gain-peak-protection", "--no-equalizer-2pass" };
    }
}
