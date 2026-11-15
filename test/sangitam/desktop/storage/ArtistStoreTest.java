package sangitam.desktop.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ArtistStoreTest {
    @TempDir Path folder;
    @Test void profilesListeningAndVolumeSurviveReopeningWithoutChangingTrackIdentity() throws Exception {
        Path db = folder.resolve("library.db"), path = folder.resolve("song.mp3");
        var library = new LibraryStore(db); library.initialize(); var artists = new ArtistStore(library); artists.initialize();
        artists.save(" Bach, Johann Sebastian ", new ArtistStore.Profile("My notes about the music and life.", new byte[]{1,2,3}));
        artists.addListening(Map.of(path, 12.5)); artists.addListening(Map.of(path, 7.5)); library.saveVolume(1.25);
        library = new LibraryStore(db); library.initialize(); artists = new ArtistStore(library); artists.initialize();
        var profile = artists.load("BACH, JOHANN SEBASTIAN"); assertEquals("My notes about the music and life.", profile.description());
        assertArrayEquals(new byte[]{1,2,3}, profile.portrait()); assertEquals(20d, artists.listening().get(path));
        assertEquals(1.25, library.loadVolume()); assertTrue(library.loadAll().isEmpty());
        artists.save("Bach, Johann Sebastian", new ArtistStore.Profile("Changed", null)); assertNull(artists.load("bach, johann sebastian").portrait());
        library.saveVolume(0); assertEquals(0, library.loadVolume());
    }
    @Test void invalidInputsDoNotPoisonListeningOrPreferences() throws Exception {
        var library = new LibraryStore(folder.resolve("library.db")); library.initialize(); var artists = new ArtistStore(library); artists.initialize();
        assertEquals(.72, library.loadVolume());
        artists.addListening(Map.of(folder.resolve("a"), Double.NaN, folder.resolve("b"), -5d)); assertTrue(artists.listening().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> artists.save("", new ArtistStore.Profile("", null)));
        assertThrows(IllegalArgumentException.class, () -> artists.save("Artist", new ArtistStore.Profile("a".repeat(20_001), null)));
    }
    @Test void artistTotalsPreserveListeningAfterRemovingTheFileFromTheLibrary() throws Exception {
        var library = new LibraryStore(folder.resolve("library.db")); library.initialize(); var artists = new ArtistStore(library); artists.initialize();
        Path path = folder.resolve("song.mp3");
        var track = new sangitam.desktop.model.Track(path, "Song", "Performer", "Album", 120, "MP3", "Classical", sangitam.desktop.model.TrackMetadata.empty().edit(Map.of(sangitam.desktop.model.TrackMetadata.Field.COMPOSER, "Composer"), null));
        library.saveAll(java.util.List.of(track)); artists.addListening(Map.of(path, 25d));
        assertEquals(25d, artists.artistSeconds("Composer")); assertEquals(25d, artists.artistSeconds("Performer"));
        library.removeFromLibrary(java.util.List.of(path), null); assertEquals(25d, artists.artistSeconds("Composer"));
    }
}
