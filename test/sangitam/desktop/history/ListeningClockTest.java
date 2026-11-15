package sangitam.desktop.history;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class ListeningClockTest {
    @Test void listeningUsesElapsedTimeAcrossSeekPauseAndTrackSwitchWithStallProtection() {
        var now = new AtomicLong(); var clock = new ListeningClock(now::get);
        var a = new Track(Path.of("a.mp3"), "A", "Artist", "Album", 300, "MP3"); var b = new Track(Path.of("b.mp3"), "B", "Artist", "Album", 300, "MP3");
        clock.observe(new PlaybackState(a, PlaybackState.Status.PLAYING, 0, 300, null));
        now.addAndGet(1_000_000_000); clock.observe(new PlaybackState(a, PlaybackState.Status.PLAYING, 290, 300, null));
        now.addAndGet(1_000_000_000); clock.observe(new PlaybackState(a, PlaybackState.Status.PAUSED, 291, 300, null));
        now.addAndGet(20_000_000_000L); clock.observe(new PlaybackState(b, PlaybackState.Status.PLAYING, 0, 300, null));
        now.addAndGet(100_000_000_000L); clock.observe(PlaybackState.idle());
        var result = clock.drain(); assertEquals(2d, result.get(a.path())); assertEquals(2d, result.get(b.path())); assertTrue(clock.drain().isEmpty());
    }
}
