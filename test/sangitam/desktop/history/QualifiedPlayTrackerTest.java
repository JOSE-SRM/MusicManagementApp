package sangitam.desktop.history;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;
import sangitam.desktop.playback.PlaybackState;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class QualifiedPlayTrackerTest {
    @Test void countsElapsedListeningOncePerPlayAndIgnoresSeeking() {
        AtomicLong clock = new AtomicLong(1);
        QualifiedPlayTracker tracker = new QualifiedPlayTracker(clock::get);
        Track track = new Track(Path.of("song.mp3"), "Song", "Artist", "Album", 10, "MP3");
        assertFalse(tracker.observe(state(track, PlaybackState.Status.PLAYING, 9), 1));
        for (int second = 1; second <= 5; second++) {
            clock.addAndGet(1_000_000_000L);
            assertEquals(second == 5, tracker.observe(state(track, PlaybackState.Status.PLAYING, 9), 1));
        }
        assertFalse(tracker.observe(state(track, PlaybackState.Status.ENDED, 10), 1));
        assertFalse(tracker.observe(state(track, PlaybackState.Status.PLAYING, 0), 2));
    }

    private static PlaybackState state(Track track, PlaybackState.Status status, double position) {
        return new PlaybackState(track, status, position, 10, null);
    }
}
