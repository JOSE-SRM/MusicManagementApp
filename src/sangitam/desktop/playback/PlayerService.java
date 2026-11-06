package sangitam.desktop.playback;

import sangitam.desktop.model.Track;

import java.util.function.Consumer;

/** Audio contract used by the Swing UI; a different decoder can replace the JavaFX adapter. */
public interface PlayerService extends AutoCloseable {
    void setListener(Consumer<PlaybackState> listener);
    PlaybackState state();
    default void prepare() { }
    default void play(Track track) { play(track, 0); }
    void play(Track track, double startSeconds);
    void pause();
    void resume();
    default void stop() { pause(); }
    void seek(double fraction);
    /** VLC-style volume ratio: 0 is mute, 1 is 100%, 1.5 is 150%. */
    void setVolume(double volume);
    default double maximumVolume() { return AudioGain.MAX_VOLUME; }
    default void setAudioSettings(AudioSettings settings) { }
    default AudioStatus audioStatus() { return AudioStatus.checking(); }
    default boolean supportsSpectrum() { return false; }
    /** Null detaches analysis. Magnitudes in dB are delivered on the Swing EDT. */
    default void setSpectrumListener(Consumer<float[]> listener) { }
    @Override void close();
}
