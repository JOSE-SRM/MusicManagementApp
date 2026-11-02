package sangitam.desktop.history;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FavoritesAnalyzerTest {
    @Test void mergesReportsFromDifferentSourcesButKeepsRealRepeats() {
        long now = System.currentTimeMillis();
        long old = now - Duration.ofDays(45).toMillis();
        Path path = Path.of("/music/song.mp3");
        Track local = new Track(path, "Blue Hour", "Aster", "Album", 180, "MP3");
        List<ListenEvent> events = List.of(
            event("Sangitam", "local-1", old, path),
            event("Spotify", "sp-1", old + 20_000, null),
            event("Sangitam", "local-2", old + 80_000, path),
            event("ListenBrainz", "lb-2", old + 85_000, null));

        Favorite favorite = FavoritesAnalyzer.analyze(List.of(local), Map.of(path, 3), events).get(0);
        // Two real local listens, two matching external reports, and one legacy count.
        assertEquals(3, favorite.knownPlays());
        assertEquals(path, favorite.localPath());
        assertEquals(1, FavoritesAnalyzer.forgotten(List.of(favorite), now).size());
        assertTrue(favorite.sources().containsAll(List.of("Sangitam", "Spotify", "ListenBrainz")));
    }

    @Test void unknownLegacyDateIsNotDeclaredForgotten() {
        Path path = Path.of("/music/old.mp3");
        Track local = new Track(path, "Old Song", "Artist", "Album", 180, "MP3");
        Favorite favorite = FavoritesAnalyzer.analyze(List.of(local), Map.of(path, 20), List.of()).get(0);
        assertEquals(20, favorite.knownPlays());
        assertEquals(0, favorite.lastHeardMillis());
        assertTrue(FavoritesAnalyzer.forgotten(List.of(favorite), System.currentTimeMillis()).isEmpty());
    }

    private static ListenEvent event(String source, String key, long at, Path path) {
        return new ListenEvent(source, key, "Blue Hour", "Aster", "Album", at,
            URI.create("https://example.com/song"), path);
    }
}
