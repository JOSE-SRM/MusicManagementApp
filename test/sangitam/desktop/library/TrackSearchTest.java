package sangitam.desktop.library;

import org.junit.jupiter.api.Test;
import sangitam.desktop.model.Track;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Search uses the imported track model and offers album/artist facets. */
class TrackSearchTest {
    @Test void filtersTitleArtistAlbumAndGenreWithoutCaseSensitivity() {
        Track first = new Track(Path.of("/music/one.mp3"), "Morning Light", "Aster", "Blue Hours", 210, "MP3", "Ambient");
        Track second = new Track(Path.of("/music/two.wav"), "Night Walk", "Mira", "Blue Hours", 188, "WAV");
        Track third = new Track(Path.of("/music/three.mp3"), "Open Road", "Sol", "Travels", 199, "MP3");
        List<Track> library = List.of(first, second, third);

        assertEquals(List.of(first), TrackSearch.find(library, "MORNING").songs());
        assertEquals(List.of(second), TrackSearch.find(library, "mira").songs());
        TrackSearch.Results album = TrackSearch.find(library, "blue hours");
        assertEquals(List.of(first, second), album.songs());
        assertEquals(List.of("Blue Hours"), album.albums());
        assertEquals(List.of("Aster", "Mira"), album.artists());
        assertEquals(List.of(first), TrackSearch.find(library, "AMBIENT").songs());
        assertTrue(TrackSearch.find(library, "missing").songs().isEmpty());
    }
}
