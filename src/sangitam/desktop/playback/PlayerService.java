package sangitam.desktop.playback;

import sangitam.desktop.model.Track;

import java.util.function.Consumer;

/** Audio contract used by the Swing UI; a different decoder can replace the JavaFX adapter. */
public interface PlayerService extends AutoCloseable {
    void setListener(Consumer<PlaybackState> listener);
    PlaybackState state();
    default void play(Track track) { play(track, 0); }
    void play(Track track, double startSeconds);
    void pause();
    void resume();
    void seek(double fraction);
    void setVolume(double volume);
    @Override void close();
}
