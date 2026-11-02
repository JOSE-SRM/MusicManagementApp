package sangitam.desktop.playback;

import sangitam.desktop.model.Track;

import java.util.function.Consumer;

/** Audio contract used by the Swing UI; a different decoder can replace the JavaFX adapter. */
public interface PlayerService extends AutoCloseable {
    void setListener(Consumer<PlaybackState> listener);
    PlaybackState state();
    void play(Track track);
    void pause();
    void resume();
    void seek(double fraction);
    void setVolume(double volume);
    @Override void close();
}
